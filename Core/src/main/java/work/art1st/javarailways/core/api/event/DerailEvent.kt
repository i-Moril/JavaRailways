package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.train.Train
/** Synchronous derailment notification owned by the simulation/main context. */
class DerailEvent(
    override val train: Train,
    val msg: String
) : MainThreadTrainEvent
