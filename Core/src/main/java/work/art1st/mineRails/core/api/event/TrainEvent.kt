package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.physics.train.CoreTrain

interface TrainEvent : Event {
    val train: CoreTrain
}