package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.physics.train.Train

interface TrainEvent : Event {
    val train: Train
}