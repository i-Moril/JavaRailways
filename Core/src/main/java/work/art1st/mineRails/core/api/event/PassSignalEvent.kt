package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.CoreTrain

class PassSignalEvent(
    override val train: CoreTrain,
    val signal: Signal
) : TrainEvent {
}