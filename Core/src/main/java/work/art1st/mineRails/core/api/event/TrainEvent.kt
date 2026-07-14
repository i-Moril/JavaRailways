package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.physics.train.Train

interface TrainEvent : Event {
    val train: Train
}

/** Train event published on the platform main thread. */
interface MainThreadTrainEvent : TrainEvent, MainThreadRailEvent

/** Train event published synchronously on a physics worker. */
interface PhysicsTrainEvent : TrainEvent, PhysicsRailEvent
