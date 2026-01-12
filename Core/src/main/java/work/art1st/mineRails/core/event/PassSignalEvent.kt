package work.art1st.mineRails.core.event

import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.Train

class PassSignalEvent(
    val train: Train,
    val signal: Signal
) : RailEvent {
}