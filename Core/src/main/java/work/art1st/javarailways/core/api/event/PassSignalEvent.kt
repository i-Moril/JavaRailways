package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.control.signal.Signal

@AsyncEventApi
class PassSignalEvent(
    override val train: Train,
    val signal: Signal
) : PhysicsTrainEvent
