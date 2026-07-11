package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi

@AsyncEventApi
class PassSignalEvent(
    override val train: Train,
    val signal: Signal
) : TrainEvent {
}